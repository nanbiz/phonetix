<script lang="ts">
  // A line of text the reader types: an address, a name, a key.
  //
  // Written back when they leave it or press enter rather than on every keystroke, because a
  // half-typed address is a host nothing can be fetched from and every letter of it would be
  // one more thing watching this setting has to react to.

  interface Props {
    value: string;
    label: string;
    placeholder?: string;
    kind?: 'text' | 'url';
    change: (value: string) => void;
  }

  let { value, label, placeholder = '', kind = 'text', change }: Props = $props();
</script>

<label class="field">
  <input
    type={kind}
    aria-label={label}
    {placeholder}
    {value}
    onchange={(event) => change((event.currentTarget as HTMLInputElement).value.trim())}
    onkeydown={(event) => {
      if (event.key === 'Enter') (event.currentTarget as HTMLInputElement).blur();
    }}
  />
</label>
